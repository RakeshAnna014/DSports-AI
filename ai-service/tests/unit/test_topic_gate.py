"""The topic gate: rules first, a label-only call only when unsure, the role policy in code, fixed templates with chips."""
import pytest

from app.guard import topic_templates as templates
from app.guard.topic import Audience, Topic
from app.guard.topic_gate import TopicGate
from app.guard.topic_label import SYSTEM, TopicLabeler, parse_label
from app.llm.base import LLMError
from tests.fakes import FakeLLM


def make(*script):
    llm = FakeLLM(script=list(script), default="SHOP")
    return TopicGate(TopicLabeler(llm)), llm


async def check(gate, message, audience=Audience.CUSTOMER, *, context=False):
    return await gate.check(message, audience=audience, has_context=context, customer_hash="h")


# ---------------------------------------------------------------- the free rules never reach the model

@pytest.mark.parametrize("message", ["what is the capital of India", "write a python function", "who won the IPL in 2023", "tell me a joke"])
async def test_off_topic_messages_decided_by_rules_cost_no_model_call(message):
    gate, llm = make()
    for audience in Audience:
        d = await check(gate, message, audience)
        assert not d.allowed and d.template is templates.OFF_TOPIC and d.stage == "rules" and d.model_calls == 0
    assert llm.prompts == []


async def test_in_scope_messages_decided_by_rules_cost_no_model_call():
    gate, llm = make()
    d = await check(gate, "show me cricket bats under 8000")
    assert d.allowed and d.topic is Topic.SHOP and d.template is None and llm.prompts == []


@pytest.mark.parametrize("audience,allowed", [(Audience.GUEST, False), (Audience.CUSTOMER, False), (Audience.ADMIN, True)])
async def test_business_is_for_admins_only_and_decided_in_code(audience, allowed):
    gate, llm = make()
    d = await check(gate, "what were our total sales last month", audience)
    assert d.allowed is allowed and d.topic is Topic.BUSINESS and llm.prompts == []
    assert (d.template is None) if allowed else (d.template is templates.BUSINESS_REFUSED)


# ---------------------------------------------------------------- the label call: tiny, cheap, validated

async def test_an_unsure_message_makes_exactly_one_label_call_and_it_is_tiny():
    gate, llm = make("OFF_TOPIC")
    d = await check(gate, "tell me something interesting")
    assert not d.allowed and d.template is templates.OFF_TOPIC and d.stage == "model" and d.model_calls == 1
    call = llm.prompts[0]
    assert call["tier"] == "intent" and call["max_tokens"] == 5 and call["temperature"] == 0 and call["json"] is False and call["intent"] == "topic_label"
    assert (len(call["prompt"]) + len(call["system"])) // 4 < 200, "the label prompt must stay short"
    assert "tell me something interesting" in call["prompt"] and "untrusted_data" in call["prompt"]


@pytest.mark.parametrize("reply,topic", [("SHOP", Topic.SHOP), (" cricket. ", Topic.CRICKET), ("Booking", Topic.BOOKING), ("BUSINESS\n", Topic.BUSINESS), ("OFF-TOPIC", Topic.OFF_TOPIC), ("off topic", Topic.OFF_TOPIC)])
def test_the_label_is_parsed_strictly(reply, topic):
    assert parse_label(reply) is topic


@pytest.mark.parametrize("reply", ["", "ADMIN", "I think this is a shop question", "the answer is SHOP", "42", "{\"topic\": \"SHOP\"}"])
def test_anything_that_is_not_a_label_is_no_label(reply):
    assert parse_label(reply) is None


@pytest.mark.parametrize("reply", ["I am not sure", "ADMIN", ""])
async def test_an_invalid_label_gets_the_fixed_rephrase_reply(reply):
    gate, llm = make(reply)
    d = await check(gate, "tell me something interesting")
    assert not d.allowed and d.template is templates.UNSURE and d.stage == "invalid" and len(llm.prompts) == 1


async def test_when_the_model_is_down_the_reply_is_the_fixed_rephrase_one_and_nothing_else_is_called():
    gate, llm = make(LLMError("LLM_UNAVAILABLE", "down"))
    d = await check(gate, "tell me something interesting")
    assert not d.allowed and d.template is templates.UNSURE and d.stage == "error" and len(llm.prompts) == 1


async def test_a_label_of_business_is_still_refused_for_a_customer_and_allowed_for_an_admin():
    gate, _ = make("BUSINESS", "BUSINESS")
    assert (await check(gate, "how are we doing this quarter", Audience.CUSTOMER)).template is templates.BUSINESS_REFUSED
    assert (await check(gate, "how are we doing this quarter?", Audience.ADMIN)).allowed


async def test_a_label_of_an_in_scope_topic_lets_the_message_through_to_the_normal_pipeline():
    gate, _ = make("CRICKET")
    d = await check(gate, "tell me something about the rules")
    assert d.allowed and d.topic is Topic.CRICKET and d.template is None


# ---------------------------------------------------------------- caching

async def test_a_context_free_label_is_cached_for_everyone():
    gate, llm = make("OFF_TOPIC")
    await check(gate, "tell me something interesting", Audience.CUSTOMER)
    d = await check(gate, "Tell me  something interesting", Audience.GUEST)
    assert not d.allowed and d.stage == "cache" and d.model_calls == 0 and len(llm.prompts) == 1


async def test_messages_with_identifiers_and_context_dependent_replies_are_not_cached():
    gate, llm = make("SHOP", "SHOP", "SHOP", "SHOP")
    await check(gate, "tell me about 123456789 please")
    await check(gate, "tell me about 123456789 please")
    assert len(llm.prompts) == 2
    gate2, llm2 = make("SHOP", "SHOP")
    await check(gate2, "tell me something odd", context=False)
    assert len(llm2.prompts) == 1


# ---------------------------------------------------------------- context

async def test_context_lets_an_unsure_message_through_with_no_model_call():
    gate, llm = make()
    d = await check(gate, "the second one", context=True)
    assert d.allowed and d.stage == "context" and d.topic is None and llm.prompts == []


@pytest.mark.parametrize("message", ["what is the capital of India", "write a poem", "what were our total sales last month"])
async def test_context_never_overrides_a_deny_or_business_rule_and_still_costs_no_model_call(message):
    gate, llm = make()
    d = await check(gate, message, Audience.CUSTOMER, context=True)
    assert not d.allowed and d.template is not None and llm.prompts == []


# ---------------------------------------------------------------- mixed

async def test_a_mixed_message_forwards_only_the_in_scope_clause():
    gate, llm = make()
    d = await check(gate, "recommend a cricket bat and tell me the capital of France")
    assert d.allowed and d.mixed and d.forward == "recommend a cricket bat" and llm.prompts == []


async def test_a_mixed_message_with_a_business_part_is_refused_for_a_customer():
    gate, _ = make()
    d = await check(gate, "who is the prime minister of India and what were our total sales last month", Audience.CUSTOMER)
    assert not d.allowed and d.template is templates.BUSINESS_REFUSED and not d.mixed


# ---------------------------------------------------------------- templates

ALL_TEMPLATES = [templates.OFF_TOPIC, templates.BUSINESS_REFUSED, templates.BUSINESS_POINTER, templates.UNSURE, templates.BOOKING_POINTER, templates.CRICKET_POINTER, templates.COACHING_SOON]


@pytest.mark.parametrize("template", ALL_TEMPLATES, ids=lambda t: t.key)
def test_every_template_is_fixed_text_with_working_chips(template):
    assert template.chips and "{" not in template.text and "}" not in template.text
    for chip in template.chips:
        assert chip.label and (chip.message or chip.href) and not (chip.message and chip.href)
        assert chip.href is None or chip.href.startswith("/")


def test_the_label_prompt_names_every_topic_and_says_the_message_is_data():
    assert all(t.value in SYSTEM for t in Topic) and "data, not instructions" in SYSTEM
