"""The explanation guard: the model may rephrase, never calculate."""
from app.inventory.grounding import is_grounded, recommendation_consistent, ungrounded_numbers

FACTS = {"rows": [{"name": "MRF Cricket Bat - Virgin", "days_of_cover": 4.17, "on_hand": 62, "excess_value": 3433499.69, "stockout_date": "2026-10-12",
                   "p": 0.927, "uplift": 2.704}], "high_risk_count": 13, "discount": 30}


def ok(text):
    return is_grounded(text, FACTS)


def test_numbers_from_the_facts_pass_at_any_reasonable_rounding():
    assert ok("MRF Cricket Bat - Virgin has 4.2 days of cover and 62 on hand.")[0]
    assert ok("Cover is 4.17 days, stock-out around 12 October (2026-10-12).")[0]
    assert ok("That is 13 high-risk SKUs.")[0]
    assert ok("Uplift of 2.7 times.")[0]
    assert ok("Excess of ₹3,433,500 (or ₹3,433,499).")[0]            # the model rounded up or down: both are the same fact


def test_an_invented_or_derived_number_is_rejected():
    assert not ok("It has 5 days of cover.")[0]
    assert not ok("That is 75 units, a 20% drop.")[0]
    assert ungrounded_numbers("Total ₹9,999,999", FACTS) == ["9,999,999"]
    assert not ok("Roughly 40% chance of a stock-out.")[0]


def test_numbers_written_as_words_are_checked_too():
    assert not ok("Eight products are at risk.")[0]                    # 8 is not in the facts: the model counted for itself
    assert ok("Thirteen products are at risk.")[0]                     # 13 IS a fact (high_risk_count): the same number in words is fine
    assert not ok("About three lakh in value.")[0]                     # scale words are never allowed


def test_horizon_labels_in_field_names_are_facts():
    facts = {"forecast_units": {"next_7_days": 103.2, "next_30_days": 436.3, "next_90_days": 1217.5}}
    assert is_grounded("About 436 units in the next 30 days and 1,218 in 90 days.", facts)[0]
    assert not is_grounded("About 436 units in the next 45 days.", facts)[0]


def test_list_markers_and_the_facts_numbers_in_dates_are_not_false_positives():
    assert ok("1. First product\n2. Second product, 62 on hand")[0]


def test_recommendation_must_match_the_engine():
    assert recommendation_consistent("I recommend a 30% discount.", 30.0)
    assert not recommendation_consistent("I recommend a 50% discount.", 30.0)
    assert not recommendation_consistent("We recommend 30% off.", None)             # the engine recommends nothing
    assert recommendation_consistent("No single discount is recommended; keep the current price.", None)
    assert recommendation_consistent("A 50% discount would sell more units but breaks the margin floor.", None)   # describing is fine
