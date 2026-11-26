"""Topic gate metrics: refusals by topic and role, label calls, and the tokens-saved ESTIMATE (never a made-up number)."""
import pytest

from app.agent.schemas import ChatRequest
from app.auth.jwt import verify_token
from app.guard import topic_metrics
from app.llm.base import LLMError
from app.obs import metrics
from tests.conftest import make_token
from tests.helpers import make_rig


def value(name: str, **labels) -> float:
    return metrics.REGISTRY.get_sample_value(name, labels) or 0.0


@pytest.fixture
async def rig():
    return await make_rig()


async def say(rig, message, principal="alice"):
    return await rig.chat.chat(None if principal is None else getattr(rig, principal, principal), ChatRequest(message=message))


async def test_a_refusal_is_counted_by_topic_and_role(rig):
    before = value("ai_topic_refusals_total", topic="OFF_TOPIC", audience="customer"), value("ai_topic_refusals_total", topic="BUSINESS", audience="guest")
    await say(rig, "what is the capital of India")
    await say(rig, "what were our total sales last month", principal=None)
    assert value("ai_topic_refusals_total", topic="OFF_TOPIC", audience="customer") == before[0] + 1
    assert value("ai_topic_refusals_total", topic="BUSINESS", audience="guest") == before[1] + 1


async def test_decisions_are_counted_by_stage_and_outcome(rig):
    allowed = value("ai_topic_decisions_total", audience="customer", stage="rules", decision="allowed")
    mixed = value("ai_topic_decisions_total", audience="customer", stage="rules", decision="mixed")
    await say(rig, "show me cricket bats")
    await say(rig, "show me cricket bats and tell me a joke")
    assert value("ai_topic_decisions_total", audience="customer", stage="rules", decision="allowed") == allowed + 1
    assert value("ai_topic_decisions_total", audience="customer", stage="rules", decision="mixed") == mixed + 1


async def test_label_calls_are_counted_by_result(rig):
    model, error, cache = (value("ai_topic_label_calls_total", result=r) for r in ("model", "error", "cache"))
    rig.llm.label_script = ["OFF_TOPIC", LLMError("LLM_UNAVAILABLE", "down")]
    await say(rig, "tell me something interesting")
    await say(rig, "tell me something puzzling")
    await say(rig, "tell me something interesting")      # now cached
    assert value("ai_topic_label_calls_total", result="model") == model + 1
    assert value("ai_topic_label_calls_total", result="error") == error + 1
    assert value("ai_topic_label_calls_total", result="cache") == cache + 1


async def test_a_rules_refusal_saves_the_measured_classifier_average_and_an_allowed_turn_saves_nothing(rig):
    topic_metrics.CLASSIFIER_USAGE._avg = 1000.0
    before = value("ai_topic_tokens_saved_estimate_total", audience="customer")
    await say(rig, "show me cricket bats")
    assert value("ai_topic_tokens_saved_estimate_total", audience="customer") == before
    await say(rig, "write a poem about the ocean")
    assert value("ai_topic_tokens_saved_estimate_total", audience="customer") == before + 1000


async def test_the_label_calls_own_tokens_are_subtracted_from_the_saving(rig):
    topic_metrics.CLASSIFIER_USAGE._avg = 1000.0
    rig.llm.label_script = ["OFF_TOPIC"]
    before = value("ai_topic_tokens_saved_estimate_total", audience="customer")
    r = await say(rig, "tell me something interesting")
    spent = r.meta.tokens_in + r.meta.tokens_out
    assert 0 < spent < 400
    assert value("ai_topic_tokens_saved_estimate_total", audience="customer") == before + 1000 - spent


def test_before_anything_is_measured_the_estimate_comes_from_the_classifier_prompt_size():
    fresh = topic_metrics.ClassifierUsage()
    assert 200 < fresh.average() < 2000
    fresh.observe(500, 100)
    assert fresh.average() == 600
    fresh.observe(0, 0)                      # an empty measurement changes nothing
    assert fresh.average() == 600


async def test_the_metric_labels_never_contain_message_text(rig):
    await say(rig, "what is the capital of Atlantis, secret-marker-9137")
    assert b"secret-marker-9137" not in metrics.render() and b"Atlantis" not in metrics.render()
