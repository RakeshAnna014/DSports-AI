import pytest
from pydantic import ValidationError

from app.config.settings import Settings
from tests.conftest import HASH_KEY, JWT_SECRET, make_settings


def test_defaults_come_from_documented_values():
    s = make_settings()
    assert (s.intent_model, s.reasoning_model, s.embedding_model, s.embedding_dim) == (
        "gemini-2.5-flash-lite", "gemini-2.5-flash", "text-embedding-005", 768)
    assert (s.max_tokens, s.top_k, s.max_tool_calls) == (1024, 5, 8)
    assert s.database_url is None


def test_every_documented_env_var_is_read(monkeypatch):
    for key, value in {
        "JWT_SECRET": JWT_SECRET, "AI_CUSTOMER_HASH_KEY": HASH_KEY,
        "AI_INTENT_MODEL": "m-intent", "AI_REASONING_MODEL": "m-reason", "AI_EMBEDDING_MODEL": "m-embed",
        "AI_EMBEDDING_DIM": "256", "AI_MAX_TOKENS": "321", "AI_TEMPERATURE": "0.7", "AI_TOP_K": "9",
        "AI_MAX_TOOL_CALLS": "3", "AI_TIMEOUT_S": "12.5", "DSPORTS_BASE_URL": "https://shop.example.test/",
        "AI_DATABASE_URL": "postgresql://u:p@db:5432/dsports_ai", "GOOGLE_CLOUD_PROJECT": "some-project",
    }.items():
        monkeypatch.setenv(key, value)
    s = Settings(_env_file=None)
    assert (s.intent_model, s.reasoning_model, s.embedding_model, s.embedding_dim) == ("m-intent", "m-reason", "m-embed", 256)
    assert (s.max_tokens, s.temperature, s.top_k, s.max_tool_calls, s.timeout_s) == (321, 0.7, 9, 3, 12.5)
    assert s.dsports_base_url == "https://shop.example.test"  # trailing slash removed
    assert s.google_cloud_project == "some-project"
    assert s.database_url.get_secret_value().startswith("postgresql://")


@pytest.mark.parametrize("overrides", [
    {"JWT_SECRET": "too-short"},
    {"AI_CUSTOMER_HASH_KEY": "short"},
    {"DSPORTS_BASE_URL": "localhost:8080"},
    {"AI_TEMPERATURE": 3},
    {"AI_MAX_TOKENS": 0},
    {"AI_TIMEOUT_S": 0},
    {"AI_TOP_K": 0},
    {"AI_LOG_LEVEL": "LOUD"},
    {"AI_PRICE_TABLE_JSON": "not json"},
    {"AI_PRICE_TABLE_JSON": "[]"},
    {"AI_PRICE_TABLE_JSON": '{"m": {"input_per_1m": 1}}'},
    {"AI_PRICE_TABLE_JSON": '{"m": {"input_per_1m": -1, "output_per_1m": 1}}'},
    {"GOOGLE_APPLICATION_CREDENTIALS": "/definitely/not/here.json"},
])
def test_invalid_configuration_is_rejected(overrides):
    with pytest.raises(ValidationError):
        make_settings(**overrides)


def test_secrets_are_required(monkeypatch):
    monkeypatch.delenv("JWT_SECRET", raising=False)
    monkeypatch.delenv("AI_CUSTOMER_HASH_KEY", raising=False)
    with pytest.raises(ValidationError):
        Settings(_env_file=None)


def test_public_view_never_contains_secrets(tmp_path):
    credentials = tmp_path / "key.json"
    credentials.write_text("{}")
    s = make_settings(AI_DATABASE_URL="postgresql://user:hunter2@db/dsports_ai",
                      GOOGLE_APPLICATION_CREDENTIALS=str(credentials))
    rendered = repr(s.public_view()) + repr(s)
    for secret in (JWT_SECRET, HASH_KEY, "hunter2", str(credentials)):
        assert secret not in rendered
    assert s.public_view()["database_configured"] is True


def test_price_table_is_operator_supplied_only():
    assert make_settings().price_table == {}
    s = make_settings(AI_PRICE_TABLE_JSON='{"m": {"input_per_1m": 0.5, "output_per_1m": 1.5}}')
    assert s.price_table["m"]["output_per_1m"] == 1.5
