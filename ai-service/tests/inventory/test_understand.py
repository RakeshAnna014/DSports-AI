"""Business-question understanding is rules-first (no model call). These are the questions from the Phase 2 brief plus phrasings that must NOT be misrouted."""
import pytest

from app.inventory import understand as U
from app.inventory.understand import Intent

SPORTS = ["Cricket", "Football", "Tennis", "Table Tennis", "Running"]
CATS = {"Bat", "Ball", "Gloves", "Shoes", "Jersey", "Kit Bag"}
NAMES = ["MRF Cricket Bat - Virgin", "SS Cricket Bat - Prime", "Nike Football Jersey - Brazil"]


def u(message, previous=None):
    return U.understand(message, previous, SPORTS, CATS, NAMES)


@pytest.mark.parametrize("question,intent", [
    ("Which products are at risk of stock-out?", Intent.STOCK_RISK),
    ("Which products are likely to stock out next month?", Intent.STOCK_RISK),
    ("Why is cricket inventory at risk?", Intent.STOCK_RISK),
    ("What are the top 10 inventory risks?", Intent.STOCK_RISK),
    ("Which products should we replenish this week?", Intent.REPLENISH),
    ("What should we replenish before the tournament?", Intent.EVENT),        # the event answer already contains the event-adjusted replenishment
    ("What should we order this week?", Intent.REPLENISH),
    ("Which products are overstocked?", Intent.OVERSTOCK),
    ("Show me overstocked products.", Intent.OVERSTOCK),
    ("Show me slow-moving products.", Intent.SLOW_MOVING),
    ("Which products are older than 90 days?", Intent.SLOW_MOVING),
    ("Which products have been sitting in inventory for more than 90 days?", Intent.SLOW_MOVING),
    ("Which products should go on clearance?", Intent.CLEARANCE),
    ("What happens if we give 30% discount?", Intent.PROMOTION),
    ("What happens if we run a flat 50% sale?", Intent.PROMOTION),
    ("Which scenario is better?", Intent.COMPARE_SCENARIOS),
    ("Which discount gives the best inventory reduction without destroying margin?", Intent.COMPARE_SCENARIOS),
    ("Which option gives us the best balance between revenue, margin and inventory reduction?", Intent.COMPARE_SCENARIOS),
    ("How will the upcoming cricket tournament affect demand?", Intent.EVENT),
    ("Do we have enough cricket inventory for the tournament?", Intent.EVENT),
    ("Are we ready for the tournament?", Intent.EVENT),
    ("Which region needs more cricket products?", Intent.REGION),
    ("Where should we move excess inventory?", Intent.REGION),
    ("Which region will likely need more running shoes?", Intent.REGION),
    ("Summarize inventory health for management.", Intent.OVERVIEW),
    ("Why is demand increasing for cricket bats?", Intent.FORECAST),
    ("What is our replenishment policy for high-risk inventory?", Intent.POLICY),
    ("What is the minimum order quantity for this supplier?", Intent.POLICY),
    ("What is our clearance policy for inventory older than 90 days?", Intent.POLICY),
    ("How accurate is the forecast model?", Intent.QUALITY),
    ("Who needs to approve a discount above 50%?", Intent.POLICY),
    ("Which swimming products will sell out before the Olympics?", Intent.STOCK_RISK),
    ("Which items are running low?", Intent.STOCK_RISK),
])
def test_business_questions_are_routed(question, intent):
    assert u(question).intent is intent, question
    assert u(question).method in ("rules", "followup")


@pytest.mark.parametrize("request_text", [
    "Create a purchase order for 100 cricket bats",
    "Change the price of the MRF cricket bat to 5000",
    "Please launch a 30% discount promotion on jerseys",
    "Go ahead and transfer 200 units of stock to the East warehouse",
    "Apply a 50% discount to all cricket products",
])
def test_write_requests_are_recognised_and_refused(request_text):
    assert u(request_text).intent is Intent.REFUSE_WRITE


@pytest.mark.parametrize("question", [
    "What happens if we apply a 30% discount?",      # a what-if is read-only even though it contains "apply"
    "Which products should we order this week?",
    "Where should we move excess inventory?",
])
def test_read_only_questions_are_never_treated_as_write_requests(question):
    assert u(question).intent is not Intent.REFUSE_WRITE


def test_entities_sport_category_product_discounts_horizon():
    e = u("What happens if we give 30% and 50% discount on slow-moving tennis bats?").entities
    assert e.sport == "Tennis" and e.category == "Bat" and e.discounts == [30.0, 50.0] and e.slow_only
    assert u("Show cricket stock that will run out next month").entities.within_days == 30
    assert u("Which products are older than 120 days?").entities.older_than_days == 120
    assert u("top 5 inventory risks").entities.top == 5
    assert u("Show table tennis bats").entities.sport == "Table Tennis"      # the longer sport name wins over "tennis"
    assert u("How is the MRF Cricket Bat - Virgin doing?").entities.product == "MRF Cricket Bat - Virgin"


def test_followups_reuse_the_previous_turn():
    first = u("Which products are likely to stock out next month?")
    memory = {"intent": first.intent.value, "entities": first.entities.to_json()}
    why = u("Why?", memory)
    assert why.intent is Intent.STOCK_RISK and why.entities.why and why.entities.within_days == 30 and why.method == "followup"
    promo = u("What happens if we give 30% discount?")
    better = u("Which scenario is better?", {"intent": promo.intent.value, "entities": promo.entities.to_json()})
    assert better.intent is Intent.COMPARE_SCENARIOS and better.entities.discounts == [30.0]


def test_nothing_is_guessed_for_unrelated_text():
    assert u("hello there").intent is Intent.UNKNOWN
    assert u("what is the weather in Bengaluru").intent is Intent.UNKNOWN


@pytest.mark.parametrize("question,expected", [
    ("What is the forecast for the Zorblax 9000 spaceship?", ["Zorblax"]),
    ("Which products are at risk of stock-out?", []),
    ("How is the MRF Cricket Bat - Virgin doing?", []),
    ("Which region needs more Cricket products?", []),
    ("Which Nike Football Jersey - Brazil items will run out?", []),
    ("What happens if we give 30% discount on Quuxlet jerseys?", ["Quuxlet"]),
])
def test_names_that_match_nothing_are_detected_not_silently_ignored(question, expected):
    assert u(question).entities.unknown_terms == expected
