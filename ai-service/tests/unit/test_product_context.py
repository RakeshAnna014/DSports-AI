"""Product-contextual conversation: "Ask AI" on a product, then questions about it, its reviews, comparing it, alternatives."""
import json
import uuid

import pytest

from app.agent.schemas import ChatRequest
from tests.helpers import make_rig
from tests.fakes import BAT_MRF, BAT_SG, BAT_SS

@pytest.fixture
async def rig():
    return await make_rig()

SEARCH = "I am a beginner cricket player. I need a lightweight bat under ₹8,000."


async def say(rig, message, *, conv=None, product=None, clear=False, who="alice"):
    principal = None if who is None else getattr(rig, who)
    return await rig.chat.chat(principal, ChatRequest(message=message, conversation_id=conv, selected_product_id=product, clear_product_context=clear))


async def test_ask_ai_sets_the_context_from_dsports_not_from_the_request(rig):
    r = await say(rig, "How heavy is it?", product=BAT_MRF)
    assert r.context.selected_product.product_id == BAT_MRF and r.context.selected_product.name == "MRF Cricket Bat - Virgin"
    assert r.intent == "PRODUCT_DETAIL" and r.meta.llm_calls == 0 and "1.2 kg" in r.message
    assert any(t["tool"] == "get_product" for t in r.meta.tools)


async def test_an_unknown_or_forged_product_id_is_ignored(rig):
    r = await say(rig, "How heavy is it?", product=str(uuid.uuid4()))
    assert r.context.selected_product is None and any("could not find that product" in d for d in r.disclaimers)
    bad = await say(rig, "hello", product="not-a-uuid")
    assert bad.context.selected_product is None


async def test_the_exact_conversation_search_compare_select_ask_weight_reviews_alternative(rig):
    first = await say(rig, SEARCH)
    c = first.conversation_id
    assert [p["name"] for p in first.products] == ["MRF Cricket Bat - Virgin", "SS Cricket Bat - Prime"]

    both = await say(rig, "Which one is better?", conv=c)
    assert both.comparison and len(both.comparison["products"]) == 2
    assert [p.name for p in both.context.comparison_products] == ["MRF Cricket Bat - Virgin", "SS Cricket Bat - Prime"]

    more = await say(rig, "Tell me more about the MRF one.", conv=c)
    assert more.intent == "PRODUCT_DETAIL" and more.context.selected_product.name == "MRF Cricket Bat - Virgin" and more.context.comparison_products == []
    assert "English willow" in more.message and "₹6,499.00" in more.message and more.meta.llm_calls == 0

    tennis = await say(rig, "Is it good for tennis-ball cricket?", conv=c)
    assert tennis.intent == "PRODUCT_KNOWLEDGE" and tennis.context.selected_product.name == "MRF Cricket Bat - Virgin"
    assert any(t["tool"] == "get_product" for t in tennis.meta.tools) and any(t["tool"] == "get_product_knowledge" for t in tennis.meta.tools)

    weight = await say(rig, "What is its weight?", conv=c)
    assert weight.intent == "PRODUCT_DETAIL" and "1.2 kg" in weight.message and weight.meta.llm_calls == 0
    assert [p["name"] for p in weight.products] == ["MRF Cricket Bat - Virgin"]

    reviews = await say(rig, "Show me reviews.", conv=c)
    assert reviews.intent == "PRODUCT_REVIEWS" and any(t["tool"] == "get_product_reviews" for t in reviews.meta.tools)
    assert reviews.reviews["name"] == "MRF Cricket Bat - Virgin" and reviews.reviews["total_reviews"] == 4
    assert "userId" not in json.dumps(reviews.reviews) and "user_id" not in json.dumps(reviews.reviews)

    cheaper = await say(rig, "Is there anything similar but cheaper?", conv=c)
    assert cheaper.intent == "PRODUCT_SEARCH" and any(t["tool"] == "search_products" for t in cheaper.meta.tools)
    assert all(p["price"] < 6499.0 for p in cheaper.products) and BAT_MRF not in [p["product_id"] for p in cheaper.products]
    assert cheaper.context.selected_product.name == "MRF Cricket Bat - Virgin"                  # the context survives every turn


async def test_compare_it_with_another_named_product_and_then_ask_about_them(rig):
    r = await say(rig, "Compare it with the SS bat.", product=BAT_MRF)
    assert r.intent == "COMPARE" and r.comparison
    assert [p["name"] for p in r.comparison["products"]] == ["MRF Cricket Bat - Virgin", "SS Cricket Bat - Prime"]
    assert len(r.context.comparison_products) == 2
    lighter = await say(rig, "Which one is lighter?", conv=r.conversation_id)
    assert lighter.comparison and len(lighter.comparison["products"]) == 2


async def test_cheaper_alternative_when_there_is_none_says_so_honestly(rig):
    r = await say(rig, "Is there a cheaper alternative?", product=BAT_MRF)          # the MRF bat is already the cheapest bat
    assert r.products == [] and "no cheaper bat" in r.message.lower()


async def test_lighter_alternatives_compare_real_weights(rig):
    r = await say(rig, "Show me something lighter", product=BAT_SG)                     # 1.25 kg: both other bats are lighter
    names = {p["name"] for p in r.products}
    assert names == {"MRF Cricket Bat - Virgin", "SS Cricket Bat - Prime"} and BAT_SG not in {p["product_id"] for p in r.products}


async def test_clearing_the_context_forgets_the_product(rig):
    first = await say(rig, "How heavy is it?", product=BAT_MRF)
    cleared = await say(rig, "hello", conv=first.conversation_id, clear=True)
    assert cleared.context.selected_product is None


async def test_a_guest_can_ask_about_a_product_but_not_about_orders(rig):
    r = await say(rig, "What material is it made from?", product=BAT_SS, who=None)
    assert r.status == "ok" and "English willow" in r.message
    assert (await say(rig, "Show my latest order", conv=r.conversation_id, who=None)).status == "needs_login"


async def test_a_product_context_cannot_be_borrowed_by_another_customer(rig):
    r = await say(rig, "How heavy is it?", product=BAT_MRF)
    other = await say(rig, "What is its weight?", conv=r.conversation_id, who="bob")      # someone else's conversation id is never revealed
    assert other.conversation_id != r.conversation_id and other.context.selected_product is None
