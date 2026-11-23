"""Step 1 of the topic gate (free rules) against the labelled dev set, plus the guarantees the owner asked for:
deny rules always win (also with conversation context), and ordinary multi-part shopping queries are never split."""
from pathlib import Path

import pytest
import yaml

from app.guard.input import check_input
from app.guard.topic import Topic
from app.guard.topic_rules import decide

DEV = yaml.safe_load((Path(__file__).resolve().parents[2] / "eval" / "topic" / "dev.yaml").read_text(encoding="utf-8"))
PUBLIC = {Topic.SHOP, Topic.BOOKING, Topic.CRICKET}


def _same(a: str, b: str) -> bool:
    strip = lambda s: " ".join(s.lower().strip(" ,;.&?!").split())
    return strip(a) == strip(b)


def test_the_dev_set_is_large_enough_and_covers_every_kind():
    assert len(DEV) >= 150
    assert {c["lang"] for c in DEV} >= {"en", "hinglish", "hi", "te"}
    assert {c["topic"] for c in DEV} == {t.value for t in Topic}
    assert sum(1 for c in DEV if c.get("attack")) >= 8 and sum(1 for c in DEV if c.get("forward")) >= 5
    assert len({c["id"] for c in DEV}) == len(DEV)


@pytest.mark.parametrize("case", DEV, ids=lambda c: c["id"])
def test_the_rules_never_decide_wrongly_on_the_dev_set(case):
    verdict = decide(case["text"], has_context=bool(case.get("context")))
    expected = Topic(case["topic"])
    if case.get("attack"):
        # an attack is stopped by the security guard or by the topic rules; either way no model sees it
        assert (not check_input(case["text"]).allowed) or verdict.topic is Topic.OFF_TOPIC, (case["text"], verdict)
        return
    if case.get("stage_a") == "unsure":
        assert verdict.unsure or verdict.context, f"the rules were expected to leave this to the label call: {verdict}"
        return
    assert not verdict.unsure, f"the rules should have decided: {case['text']!r}"
    if case.get("forward"):
        assert verdict.mixed and _same(verdict.forward, case["forward"]), verdict
        assert verdict.topic is expected
        return
    assert not verdict.mixed, verdict
    if expected in PUBLIC:
        assert verdict.topic in PUBLIC, f"an in-scope message was refused: {case['text']!r} -> {verdict}"
    else:
        assert verdict.topic is expected, f"{case['text']!r} -> {verdict}"


def test_how_much_of_the_dev_set_the_free_rules_decide():
    decidable = [c for c in DEV if not c.get("attack")]
    decided = [c for c in decidable if not decide(c["text"], has_context=bool(c.get("context"))).unsure and not decide(c["text"], has_context=bool(c.get("context"))).context]
    share = len(decided) / len(decidable)
    print(f"free rules decide {len(decided)} of {len(decidable)} dev messages ({share:.0%}); the rest go to the label call")
    assert share >= 0.80


# ---------------------------------------------------------------- owner requirement 1: ordinary multi-part queries are never split

MULTI_PART_SHOPPING = [
    "bat for a 12-year-old and under 3000",
    "red or blue shoes, size 9, under 4000",
    "gloves, pads and a helmet for my son",
    "I need a bat, a ball and a kit bag",
    "show me bats and balls",
    "cricket bat and also gloves under 6000",
    "shoes for running and gym",
    "a kit bag and shoes",
    "recommend a bat, but not too heavy",
    "football and a pump",
    "track my order and show my cart",
    "refund status and return policy",
    "cheaper than MRF but still English willow, and lighter",
    "what is LBW and how is net run rate calculated",
    "show my matches and my batting stats",
    "book a court for Saturday and a ground for Sunday",
]


@pytest.mark.parametrize("text", MULTI_PART_SHOPPING)
def test_ordinary_multi_part_queries_are_never_split_or_rewritten(text):
    verdict = decide(text)
    assert verdict.topic in PUBLIC and not verdict.mixed and verdict.forward is None, verdict


# ---------------------------------------------------------------- owner requirement 2: deny rules always apply, context only fills in "unsure"

PRODUCTS_ON_SCREEN = dict(has_context=True)


@pytest.mark.parametrize("text", ["what is the capital of India", "write a poem", "who won the IPL in 2023", "explain Java streams", "tell me a joke", "solve 3x + 5 = 20"])
def test_context_never_overrides_a_deny_rule(text):
    assert decide(text, **PRODUCTS_ON_SCREEN).topic is Topic.OFF_TOPIC
    assert decide(text).topic is Topic.OFF_TOPIC


def test_context_never_overrides_the_business_rules():
    assert decide("what were our total sales last month", has_context=True).topic is Topic.BUSINESS


def test_context_only_turns_an_unsure_message_into_allowed():
    assert decide("the second one").unsure
    assert decide("the second one", has_context=True).context
    assert decide("show cricket bats", has_context=True).topic is Topic.SHOP        # a decided message keeps its own topic


# ---------------------------------------------------------------- the deny rules do not eat legitimate shopping words

@pytest.mark.parametrize("text", [
    "apply my promo code SAVE10", "my coupon code is not working", "what is the discount code for new customers", "any news on my complaint",
    "latest news on my order", "show my order history", "is there a sale on bats", "what is the price of the SS Ton bat", "how tall is the SG Players Edition",
    "when will the MRF bat be back in stock", "do you have a gift card", "who won my last match", "my team's points table",
])
def test_legitimate_messages_are_not_denied(text):
    verdict = decide(text)
    assert verdict.topic in PUBLIC or verdict.unsure, f"{text!r} -> {verdict}"
    assert verdict.topic is not Topic.OFF_TOPIC


def test_a_mixed_message_forwards_the_in_scope_clause_and_never_echoes_the_declined_one():
    verdict = decide("show cricket bats and tell me the capital of France")
    assert verdict.mixed and verdict.forward == "show cricket bats"
    assert "capital" not in (verdict.forward or "").lower()


# ---------------------------------------------------------------- cricket phrases that contain gear words, and shopping messages that contain cricket words

@pytest.mark.parametrize("text", ["explain the no-ball rule", "what is a wide ball", "what is a duck in cricket", "what is a hat-trick in cricket", "how is net run rate calculated"])
def test_cricket_rules_questions_are_cricket_even_when_they_contain_a_gear_word_or_the_word_cricket(text):
    assert decide(text).topic is Topic.CRICKET, decide(text)


@pytest.mark.parametrize("text", [
    "something for my nephew who just started playing cricket, nothing over 5000", "a cricket bat with a wide handle", "average rated cricket bats under 5000",
    "do you have wide fit cricket shoes", "cricket ball for a net session",
])
def test_shopping_messages_that_contain_cricket_and_glossary_words_stay_shopping(text):
    assert decide(text).topic is Topic.SHOP, decide(text)
