import pytest

from app.shop.catalog import Price, ProductFacts, Rating, Stock
from app.shop.ranking import LOW_STOCK_THRESHOLD, WEIGHTS, Preferences, rank, skill_factor, weight_factor


def p(name, price, *, skill=None, weight_class=None, weight_g=None, qty=20, category="Bat", sport="Cricket", rating=None, pid=None):
    attrs = {k: v for k, v in (("skillLevel", skill), ("weightClass", weight_class)) if v}
    return ProductFacts(product_id=pid or name, name=name, category=category, sport=sport, attributes=attrs, weight_g=weight_g,
                        price=Price(selling_price=price), stock=Stock(available_quantity=qty, status="IN_STOCK" if qty else "OUT_OF_STOCK"),
                        rating=Rating(average=rating[0], count=rating[1]) if rating else None)


def names(ranked):
    return [r.product.name for r in ranked]


def test_weights_sum_to_one():
    assert sum(WEIGHTS.values()) == pytest.approx(1.0)


def test_same_inputs_same_order_every_time():
    candidates = [(p("A", 5000, skill="beginner"), 0.9), (p("B", 4000, skill="beginner"), 0.9), (p("C", 3000), 0.5)]
    prefs = Preferences(skill_level="beginner", category="Bat")
    assert [names(rank(candidates, prefs)) for _ in range(5)] == [names(rank(candidates, prefs))] * 5


def test_beginner_skill_prefers_beginner_bats():
    ranked = rank([(p("Pro", 9000, skill="advanced"), 0.8), (p("Starter", 6000, skill="beginner"), 0.8), (p("Mid", 7000, skill="intermediate"), 0.8)],
                  Preferences(skill_level="beginner"))
    assert names(ranked) == ["Starter", "Mid", "Pro"]


@pytest.mark.parametrize("wanted,have,score", [("beginner", "beginner", 1.0), ("beginner", "all_levels", 0.8), ("beginner", "intermediate", 0.4),
                                                ("beginner", "advanced", 0.0), ("beginner", None, 0.5)])
def test_skill_factor_table(wanted, have, score):
    assert skill_factor(wanted, {"skillLevel": have} if have else {}) == score


def test_lightweight_preference_uses_class_and_actual_weight():
    light, medium, heavy = p("Light", 7000, weight_class="light", weight_g=1100), p("Med", 7000, weight_class="medium", weight_g=1200), \
        p("Heavy", 7000, weight_class="heavy", weight_g=1300)
    ranked = rank([(medium, 0.5), (heavy, 0.5), (light, 0.5)], Preferences(weight_pref="light"))
    assert names(ranked) == ["Light", "Med", "Heavy"]
    assert names(rank([(medium, 0.5), (heavy, 0.5), (light, 0.5)], Preferences(weight_pref="heavy"))) == ["Heavy", "Med", "Light"]


def test_weight_factor_is_none_without_data():
    assert weight_factor("light", p("X", 1), None, None) is None


def test_unmentioned_preferences_do_not_change_the_order():
    cands = [(p("A", 100, skill="advanced"), 0.2), (p("B", 100, skill="beginner"), 0.9)]
    assert names(rank(cands, Preferences())) == ["B", "A"]          # only retrieval strength decides


def test_budget_factor_marks_outside_candidates_down():
    ranked = rank([(p("Cheap", 5000), 0.5), (p("Pricey", 9000), 0.5)], Preferences(max_price=8000))
    assert names(ranked) == ["Cheap", "Pricey"] and ranked[0].factors["budget"] == 1.0 and ranked[1].factors["budget"] == 0.0


def test_category_and_sport_match():
    ranked = rank([(p("Shoe", 100, category="Shoes"), 0.9), (p("Bat", 100, category="Bat"), 0.9)], Preferences(category="Bat", sport="Cricket"))
    assert names(ranked)[0] == "Bat"


def test_low_stock_ranks_below_comfortable_stock():
    ranked = rank([(p("Low", 100, qty=LOW_STOCK_THRESHOLD - 1), 0.5), (p("Plenty", 100, qty=30), 0.5)], Preferences())
    assert names(ranked) == ["Plenty", "Low"]


def test_out_of_stock_scores_zero_availability():
    assert rank([(p("Gone", 100, qty=0), 0.5)], Preferences())[0].factors["availability"] == 0.0


def test_ratings_need_enough_reviews_and_never_beat_a_hard_mismatch():
    few = p("Few", 100, rating=(5.0, 2))
    assert "rating" not in rank([(few, 0.5)], Preferences())[0].factors
    great_but_wrong = p("Wrong", 100, skill="advanced", rating=(5.0, 50))
    right = p("Right", 100, skill="beginner", rating=(3.0, 50))
    assert names(rank([(great_but_wrong, 0.8), (right, 0.8)], Preferences(skill_level="beginner")))[0] == "Right"


def test_ties_break_on_price_then_name():
    ranked = rank([(p("B", 200), 0.5), (p("A", 200), 0.5), (p("C", 100), 0.5)], Preferences())
    assert names(ranked) == ["C", "A", "B"]


def test_empty_candidates():
    assert rank([], Preferences()) == []


def test_requested_age_group_prefers_matching_products():
    junior = ProductFacts(product_id="j", name="Junior", category="Bat", sport="Badminton", attributes={"ageGroup": "junior", "skillLevel": "beginner"},
                          price=Price(selling_price=1999), stock=Stock(available_quantity=20, status="IN_STOCK"))
    adult = ProductFacts(product_id="a", name="Adult", category="Bat", sport="Badminton", attributes={"ageGroup": "adult", "skillLevel": "beginner"},
                         price=Price(selling_price=1999), stock=Stock(available_quantity=20, status="IN_STOCK"))
    assert names(rank([(adult, 0.5), (junior, 0.5)], Preferences(age_group="junior"))) == ["Junior", "Adult"]
    assert names(rank([(adult, 0.9), (junior, 0.5)], Preferences())) == ["Adult", "Junior"]    # no preference stated: retrieval decides


def test_near_identical_retrieval_scores_cannot_outweigh_an_explicit_preference():
    """Regression: with min-max scaling, 0.0328 vs 0.0317 became 1.0 vs 0.0 and a 'heavy bat' request ranked the light bat first."""
    light = p("Light", 7500, skill="intermediate", weight_class="light", weight_g=1150)
    heavy = p("Heavy", 12000, skill="advanced", weight_class="heavy", weight_g=1250)
    ceiling = 2 / 61
    prefs = Preferences(skill_level="advanced", weight_pref="heavy", category="Bat", sport="Cricket")
    assert names(rank([(light, 0.0328), (heavy, 0.0317)], prefs, score_ceiling=ceiling)) == ["Heavy", "Light"]
    assert names(rank([(light, 0.0328), (heavy, 0.0317)], prefs)) == ["Light", "Heavy"]       # the old relative scaling got it wrong
    assert rank([(light, ceiling)], Preferences(), score_ceiling=ceiling)[0].semantic == 1.0


def test_a_clearly_better_retrieval_match_still_wins_when_preferences_are_equal():
    a, b = p("A", 100), p("B", 100)
    assert names(rank([(a, 0.0328), (b, 0.0164)], Preferences(), score_ceiling=2 / 61)) == ["A", "B"]
