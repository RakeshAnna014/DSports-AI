"""The reviewed cricket glossary (app/agent/data/cricket_glossary.json) and the fixed cricket replies. No model is ever involved."""
import json
import re

import pytest

from app.agent import cricket
from app.agent.schemas import ChatRequest
from app.guard import topic_templates as templates
from tests.helpers import make_rig

GLOSSARY = cricket.load_glossary()


def test_the_glossary_file_is_well_formed():
    raw = json.loads(cricket.GLOSSARY_PATH.read_text(encoding="utf-8"))
    assert len(raw) >= 20
    assert len({r["id"] for r in raw}) == len(raw)
    for r in raw:
        assert set(r) == {"id", "title", "terms", "answer", "source"} and r["terms"] and r["source"], r["id"]
        assert all(t == t.lower() for t in r["terms"]), f"{r['id']}: terms are lower case"
        assert 40 < len(r["answer"]) <= 700, f"{r['id']}: an answer should be short enough to read in chat"


def test_no_term_belongs_to_two_entries():
    seen: dict[str, str] = {}
    for entry in GLOSSARY:
        for term in entry.terms:
            assert term not in seen, f"{term!r} is in both {seen.get(term)} and {entry.id}"
            seen[term] = entry.id


def test_answers_make_no_claim_about_dsports_features_prices_or_people():
    text = " ".join(e.answer.lower() for e in GLOSSARY)
    for word in ("dsports", "₹", "kohli", "dhoni", "tendulkar", "ipl", "world cup"):
        assert word not in text, word
    assert not re.search(r"\brs\.? ?\d", text)


@pytest.mark.parametrize("message,entry", [
    ("what is LBW", "lbw"), ("explain the no-ball rule", "no_ball"), ("how is net run rate calculated", "net_run_rate"), ("what is a free hit", "free_hit"),
    ("what is the DLS method", "dls"), ("how does a super over work", "super_over"), ("what is a maiden over", "maiden"), ("explain powerplay overs in T20", "powerplay"),
    ("when is a batter given out stumped", "stumped"), ("what is a duck in cricket", "duck"), ("how many overs in an ODI innings", "format_overs"),
    ("how to play a cover drive", "cover_drive"), ("kya LBW out hota hai", "lbw"), ("what is strike rate", "strike_rate"), ("wicket kaise girta hai cricket mein", "ways_out"),
    ("what is a required run rate", "run_rate"), ("what does a hat-trick mean", "hat_trick"), ("what is a follow on", "follow_on"),
])
def test_a_rules_question_finds_exactly_its_entry(message, entry):
    assert [e.id for e in cricket.find(message, GLOSSARY)] == [entry]


def test_only_a_comparison_may_return_two_entries():
    assert [e.id for e in cricket.find("difference between a wide and a no ball", GLOSSARY)] == ["wide", "no_ball"] or \
           {e.id for e in cricket.find("difference between a wide and a no ball", GLOSSARY)} == {"wide", "no_ball"}
    assert len(cricket.find("what is a wide and a no ball", GLOSSARY)) == 1
    assert [e.id for e in cricket.find("what is the difference between a bye and a leg bye", GLOSSARY)] == ["byes"]


def test_a_question_about_my_own_data_is_never_answered_from_the_glossary():
    assert cricket.reply("what is my strike rate", GLOSSARY) is templates.CRICKET_POINTER
    assert cricket.reply("show my matches", GLOSSARY) is templates.CRICKET_POINTER


def test_replies_fall_back_to_fixed_texts_and_never_invent_an_answer():
    assert cricket.reply("tips to improve my bowling action", GLOSSARY) is templates.COACHING_SOON
    assert cricket.reply("what is a nightwatchman", GLOSSARY) is cricket.GLOSSARY_MISS
    assert cricket.reply("anything", []) is cricket.GLOSSARY_MISS


def test_a_glossary_reply_names_its_source_and_carries_chips():
    reply = cricket.reply("what is LBW", GLOSSARY)
    assert reply.text.startswith("LBW (leg before wicket):") and "glossary" in reply.text and "league" in reply.text and reply.chips


async def test_through_the_chat_a_rules_question_is_answered_from_the_glossary_with_no_model_call():
    rig = await make_rig()
    r = await rig.chat.chat(rig.alice, ChatRequest(message="what is LBW"))
    assert r.intent == "CRICKET_INFO" and r.message.startswith("LBW (leg before wicket):") and rig.llm.prompts == [] and r.message_source == "deterministic"
    assert (await rig.chat.chat(None, ChatRequest(message="explain the no-ball rule"))).message.startswith("No-ball:")
