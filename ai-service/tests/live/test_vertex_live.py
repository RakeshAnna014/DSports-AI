"""LIVE tests: real calls to Vertex AI with the credentials in GOOGLE_APPLICATION_CREDENTIALS. No mocks.

Not part of the default run. Execute with:  AI_RUN_LIVE_TESTS=1 uv run pytest -m live
They cost a few tokens and require network access and Vertex AI permission.
"""
import json
import os

import pytest

from app.config.settings import Settings
from app.llm.base import LLMError
from app.llm.vertex import VertexGemini
from app.obs.usage import UsageRecorder

pytestmark = [pytest.mark.live,
              pytest.mark.skipif(os.environ.get("AI_RUN_LIVE_TESTS") != "1", reason="live Vertex tests are opt-in: set AI_RUN_LIVE_TESTS=1")]


@pytest.fixture
def settings() -> Settings:
    # Deliberately reads the real environment (models, location, credentials), unlike the hermetic unit tests.
    return Settings(_env_file=None, JWT_SECRET="live-test-secret-live-test-secret-0123", AI_CUSTOMER_HASH_KEY="live-test-hash-key-0123")


class Sink:
    def __init__(self):
        self.records = []

    async def write(self, record):
        self.records.append(record)


@pytest.fixture
def sink():
    return Sink()


@pytest.fixture
def llm(settings, sink):
    return VertexGemini(settings, UsageRecorder(settings.price_table, sink))


@pytest.mark.parametrize("tier", ["intent", "reasoning"])
async def test_real_gemini_returns_json(llm, sink, tier):
    result = await llm.generate('Return a short JSON object confirming you are reachable: {"reachable": true}',
                                tier=tier, json_mode=True, max_tokens=100, temperature=0)
    assert json.loads(result.text)["reachable"] is True
    assert result.model == (llm._s.intent_model if tier == "intent" else llm._s.reasoning_model)
    assert result.tokens_in and result.tokens_out and result.latency_ms > 0
    assert sink.records[-1].tokens_in == result.tokens_in and sink.records[-1].outcome == "ok"


async def test_real_embedding_has_the_configured_dimension(llm, settings, sink):
    result = await llm.embed(["Kashmir willow cricket bat for beginners", "leather cricket ball"],
                             task_type="RETRIEVAL_DOCUMENT")
    assert result.model == settings.embedding_model
    assert result.dimension == settings.embedding_dim, "AI_EMBEDDING_DIM must match the model: a change needs a re-index"
    assert len(result.vectors) == 2 and all(len(v) == result.dimension for v in result.vectors)
    assert result.latency_ms > 0
    assert sink.records[-1].operation == "embed"


async def test_unknown_model_is_reported_as_a_stable_error_code(settings, sink):
    s = Settings(_env_file=None, JWT_SECRET="live-test-secret-live-test-secret-0123",
                 AI_CUSTOMER_HASH_KEY="live-test-hash-key-0123", AI_REASONING_MODEL="gemini-does-not-exist")
    with pytest.raises(LLMError) as err:
        await VertexGemini(s, UsageRecorder({}, sink)).generate("hi")
    assert err.value.code == "LLM_MODEL_NOT_FOUND"
    assert sink.records[-1].outcome == "LLM_MODEL_NOT_FOUND"
