"""Unit tests of the LLM layer that never contact Vertex (the live check is in tests/live)."""
import google.auth
import pytest

from app.llm.base import LLMError
from app.llm.vertex import VertexGemini, _classify
from app.obs.usage import UsageRecorder
from tests.conftest import make_settings


def test_tier_selects_the_configured_model():
    llm = VertexGemini(make_settings(AI_INTENT_MODEL="cheap", AI_REASONING_MODEL="smart"), UsageRecorder({}))
    assert (llm._model_for("intent"), llm._model_for("reasoning")) == ("cheap", "smart")


async def test_missing_credentials_fail_clearly_without_a_network_call(monkeypatch):
    def no_credentials(*a, **k):
        raise google.auth.exceptions.DefaultCredentialsError("none")
    monkeypatch.setattr(google.auth, "default", no_credentials)
    llm = VertexGemini(make_settings(), UsageRecorder({}))
    with pytest.raises(LLMError) as err:
        await llm.generate("hi")
    assert err.value.code == "LLM_NOT_CONFIGURED"
    with pytest.raises(LLMError):
        await llm.embed(["hi"], task_type="RETRIEVAL_QUERY")


def test_provider_errors_map_to_stable_codes():
    from google.genai import errors
    def api_error(code):
        return errors.APIError(code, {"error": {"code": code, "message": "x", "status": "S"}})
    assert _classify(api_error(403)) == "LLM_PERMISSION_DENIED"
    assert _classify(api_error(404)) == "LLM_MODEL_NOT_FOUND"
    assert _classify(api_error(429)) == "LLM_QUOTA"
    assert _classify(api_error(500)) == "LLM_ERROR"
    assert _classify(TimeoutError()) == "LLM_TIMEOUT"
    assert _classify(ValueError()) == "LLM_ERROR"
