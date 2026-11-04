import datetime as dt

import pytest

from app.router import tier0
from app.router.extract import (extract_budget, extract_order_ref, extract_ordinals, extract_shopping_slots, numbers_in)
from app.router.intents import Intent
from app.router.periods import parse_period
from app.store.state import ConversationState

TODAY = dt.date(2026, 10, 7)


# ---------------------------------------------------------------- budget / slots

@pytest.mark.parametrize("text,expected", [
    ("a bat under ₹8,000", {"max_price": 8000}),
    ("below 8000 rupees", {"max_price": 8000}),
    ("less than 5k", {"max_price": 5000}),
    ("within Rs. 12,500", {"max_price": 12500}),
    ("at most 2 lakh", {"max_price": 200000}),
    ("above 3000", {"min_price": 3000}),
    ("between 4000 and 7000", {"min_price": 4000, "max_price": 7000}),
    ("a bat for ₹6,499", {"max_price": 6499}),
    ("I want a bat", {}),
])
def test_budget_extraction(text, expected):
    assert extract_budget(text) == pytest.approx(expected) if expected else extract_budget(text) == {}


def test_shopping_slots_from_a_natural_request():
    slots = extract_shopping_slots("I'm a beginner cricket player. I need a lightweight bat under ₹8,000.")
    assert slots == {"sport": "Cricket", "category": "Bat", "skill_level": "beginner", "weight_pref": "light", "max_price": 8000}


@pytest.mark.parametrize("text,category", [("football boots", "Shoes"), ("a kit bag", "Kit Bag"), ("tennis racket", "Bat"), ("shin guards", "Accessories")])
def test_category_synonyms(text, category):
    assert extract_shopping_slots(text)["category"] == category


def test_order_references():
    assert extract_order_ref("where is ORD-20261007-000007?") == {"order_number": "ORD-20261007-000007"}
    assert extract_order_ref("track order 000012") == {"order_number": "000012"}
    assert extract_order_ref("where is my last order") == {"latest": True}
    assert extract_order_ref("show cricket bats") == {}


def test_ordinals():
    assert extract_ordinals("compare the first and the third") == [1, 3]
    assert extract_ordinals("option 2 please") == [2]
    assert extract_ordinals("compare these") == []


def test_numbers_in_message():
    assert {8000.0, 5000.0} <= numbers_in("under 8,000 or 5k")


# ---------------------------------------------------------------- periods

@pytest.mark.parametrize("text,since,until", [
    ("what did I order last month", dt.date(2026, 9, 1), dt.date(2026, 9, 30)),
    ("orders this month", dt.date(2026, 10, 1), dt.date(2026, 10, 7)),
    ("orders in august", dt.date(2026, 8, 1), dt.date(2026, 8, 31)),
    ("orders last 2 weeks", dt.date(2026, 9, 23), dt.date(2026, 10, 7)),
    ("orders yesterday", dt.date(2026, 10, 6), dt.date(2026, 10, 6)),
    ("last week's orders", dt.date(2026, 9, 28), dt.date(2026, 10, 4)),
])
def test_periods(text, since, until):
    p = parse_period(text, TODAY)
    assert (p.since, p.until) == (since, until)


def test_no_period():
    assert parse_period("show my orders", TODAY) is None


# ---------------------------------------------------------------- tier 0 routing (no LLM)

def route(message, conv=None, authenticated=True):
    return tier0.classify(message, conv, authenticated=authenticated, today=TODAY)


@pytest.mark.parametrize("message,intent", [
    ("Show my recent orders", Intent.ORDERS_LIST),
    ("show me my orders", Intent.ORDERS_LIST),
    ("what did I order last month?", Intent.ORDERS_LIST),
    ("order history", Intent.ORDERS_LIST),
    ("Where is order ORD-20261007-000007?", Intent.TRACK_ORDER),
    ("where is my order", Intent.TRACK_ORDER),
    ("track my package", Intent.TRACK_ORDER),
    ("Show my cart", Intent.CART_VIEW),
    ("what's in my cart", Intent.CART_VIEW),
    ("What is the status of my refund?", Intent.REFUND_STATUS),
    ("has my refund been processed", Intent.REFUND_STATUS),
    ("why haven't I received my refund?", Intent.REFUND_WHY),
    ("did my payment go through", Intent.PAYMENT_STATUS),
    ("Can I return the shoes I bought?", Intent.RETURN_CHECK),
    ("Add the same shoes I bought last time to my cart.", Intent.REORDER),
    ("order the same bat again", Intent.REORDER),
    ("hello", Intent.SMALLTALK),
    ("What is your return policy?", Intent.SUPPORT_POLICY),
    ("how long is the warranty", Intent.SUPPORT_POLICY),
    ("How should I maintain this bat?", Intent.PRODUCT_KNOWLEDGE),
    ("how do I knock in a new bat", Intent.PRODUCT_KNOWLEDGE),
    ("I need a lightweight cricket bat under 8000", Intent.PRODUCT_SEARCH),
    ("show me football boots", Intent.PRODUCT_SEARCH),
])
def test_tier0_routes_without_a_model(message, intent):
    r = route(message)
    assert r is not None and r.intent == intent and r.tier == 0


@pytest.mark.parametrize("message", [
    "something for my nephew who just started playing",
    "is it worth it?",
    "my son lost interest in sports, any thoughts",
    "what do you think about the weather",
])
def test_ambiguous_messages_fall_through_to_tier1(message):
    assert route(message) is None


def test_order_slots_are_extracted():
    assert route("track order ORD-20261007-000007").slots == {"order_number": "ORD-20261007-000007"}
    assert route("what did I order last month?").slots["since"] == "2026-09-01"


def test_refinement_needs_prior_products():
    assert route("these are too expensive") is None
    conv = ConversationState("c", "o", last_products=[{"id": "1", "name": "Bat", "price": 100}])
    r = route("These are too expensive. Show lighter options.", conv)
    assert r.intent == Intent.REFINE_SEARCH and r.slots["price_feedback"] == "lower" and r.slots["weight_pref"] == "light"


def test_compare_and_advice_use_the_shown_products():
    conv = ConversationState("c", "o", last_products=[{"id": "1", "name": "A"}, {"id": "2", "name": "B"}])
    assert route("Compare these", conv).intent == Intent.COMPARE
    assert route("Which one is better for a beginner?", conv).intent == Intent.PRODUCT_ADVICE
    assert route("compare the first and the second", conv).slots["ordinals"] == [1, 2]
    assert route("compare these", ConversationState("c", "o")) is None


def test_bare_yes_never_becomes_an_action():
    # Nothing is waiting: "yes" is answered deterministically as "nothing to confirm", whatever the context says.
    conv = ConversationState("c", "o", slots={"return_candidate": {"order_id": "1", "product_id": "2", "product_name": "Shoes"}})
    r = route("yes", conv)
    assert r.intent == Intent.CONFIRM_YES and r.tier == 0


def test_yes_and_no_with_a_pending_action():
    conv = ConversationState("c", "o", pending_nonce="n1")
    assert route("Yes, go ahead", conv).intent == Intent.CONFIRM_YES
    assert route("no thanks", conv).intent == Intent.CONFIRM_NO


def test_return_them_needs_a_return_candidate():
    assert route("return them").intent == Intent.RETURN_CHECK      # without a pending candidate it is only an eligibility check, never a creation
    conv = ConversationState("c", "o", slots={"return_candidate": {"order_id": "1", "product_id": "2", "product_name": "Shoes", "max_qty": 1}})
    assert route("Yes, return them.", conv).intent == Intent.RETURN_CREATE


def test_reorder_wins_over_cart():
    assert route("add the same shoes to my cart").intent == Intent.REORDER


def test_buying_something_is_not_a_cart_view():
    assert route("I want to buy a bat and put it in my cart").intent == Intent.PRODUCT_SEARCH


@pytest.mark.parametrize("message,intent", [
    ("what do the refund statuses mean?", Intent.SUPPORT_POLICY),
    ("what do the shipment statuses mean?", Intent.SUPPORT_POLICY),
    ("What weight of cricket bat should a beginner pick?", Intent.PRODUCT_KNOWLEDGE),
    ("I want to speak to a human agent and raise a support ticket", Intent.CASE_ESCALATE),     # Phase 3: a real workflow, no longer the "no ticketing" disclaimer
    ("Compare the MRF Cricket Bat - Virgin and the SS Cricket Bat - Prime", Intent.COMPARE),
])
def test_questions_about_meaning_and_guides_are_not_account_lookups_or_searches(message, intent):
    known = frozenset({"mrf cricket bat - virgin", "ss cricket bat - prime"})
    r = tier0.classify(message, None, authenticated=True, today=TODAY, known_products=known)
    assert r is not None and r.intent == intent and r.tier == 0


def test_age_group_is_extracted():
    assert extract_shopping_slots("badminton racket for a junior beginner")["age_group"] == "junior"
    assert "age_group" not in extract_shopping_slots("a cricket bat")


@pytest.mark.parametrize("message,hint", [
    ("Can I return the shoes I bought?", "shoes"), ("Can I return the football I bought?", "football"), ("can I return the umbrella I bought", "umbrella"),
    ("Add the same shoes I bought last time to my cart.", "shoes"), ("order the same gloves again", "gloves"), ("Can I return the Nike jersey", "nike"),
    ("can I return it", None), ("can I return my last order", None), ("order that again", None),
])
def test_the_product_the_customer_names_is_never_guessed(message, hint):
    r = tier0.classify(message, None, authenticated=True, today=TODAY)
    assert r is not None and r.slots.get("product_hint") == hint


@pytest.mark.parametrize("message", ["buy those shoes again", "order the same gloves again", "get these shoes once more", "buy it again", "order that bat again"])
def test_reorder_phrasings(message):
    r = tier0.classify(message, None, authenticated=True, today=TODAY)
    assert r is not None and r.intent == Intent.REORDER


def test_definitional_questions_about_product_terms_are_knowledge_even_after_a_search():
    conv = ConversationState("c", "o", last_products=[{"id": "1", "name": "MRF Cricket Bat - Virgin"}, {"id": "2", "name": "SS Cricket Bat - Prime"}])
    assert route("What is English willow?", conv).intent == Intent.PRODUCT_KNOWLEDGE
    assert route("What is the difference between English willow and Kashmir willow?", conv).intent == Intent.PRODUCT_KNOWLEDGE
    assert route("Which one is better for a beginner?", conv).intent == Intent.PRODUCT_ADVICE      # still advice about the shown products
