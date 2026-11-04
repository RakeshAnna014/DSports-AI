import importlib

import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from tests.conftest import make_settings, make_token


def test_application_starts_without_database_llm_or_dsports(client):
    # The fixture entered the lifespan: reaching here means startup succeeded with every dependency unavailable.
    assert client.app.state.store is None
    assert client.app.state.http is not None


def test_health_is_up_and_independent_of_dependencies(client):
    r = client.get("/health")
    assert (r.status_code, r.json()) == (200, {"status": "UP", "service": "dsports-ai"})


def test_dependency_health_reports_each_dependency(client):
    r = client.get("/health/dependencies")
    body = r.json()
    assert r.status_code == 200
    assert body["status"] == "DEGRADED"
    assert body["dependencies"]["database"]["status"] == "DISABLED"
    assert body["dependencies"]["dsports_backend"]["status"] == "DOWN"
    assert body["dependencies"]["llm"] == {"status": "NOT_CONFIGURED", "probed": False, "circuit_breaker": "closed"}
    assert body["dependencies"]["tika"]["status"] == "DISABLED"
    assert "config" not in body                                         # configuration detail is for administrators only (see tests/security)
    admin = client.get("/health/dependencies", headers={"Authorization": "Bearer " + make_token(roles=("CUSTOMER", "ADMIN"))})
    assert admin.json()["config"]["embedding_dim"] == 768
    assert "JWT_SECRET" not in r.text + admin.text and "secret" not in (r.text + admin.text).lower()


def test_dependency_health_never_calls_the_llm(client, monkeypatch):
    def boom(*a, **k):
        raise AssertionError("health must not call the LLM")
    monkeypatch.setattr(client.app.state.llm, "generate", boom)
    monkeypatch.setattr(client.app.state.llm, "embed", boom)
    assert client.get("/health/dependencies").status_code == 200


def test_unreachable_database_degrades_instead_of_failing_startup():
    settings = make_settings(AI_DATABASE_URL="postgresql://u:p@127.0.0.1:9/none", AI_TIMEOUT_S=1)
    with TestClient(create_app(settings)) as c:
        body = c.get("/health/dependencies").json()
        assert c.get("/health").status_code == 200
        assert body["dependencies"]["database"]["status"] == "DOWN"
        assert "p@" not in str(body)  # the connection string is not echoed


def test_unknown_route_uses_the_error_contract(client):
    r = client.get("/nope")
    body = r.json()
    assert (r.status_code, body["code"], body["path"]) == (404, "ROUTE_NOT_FOUND", "/nope")
    assert body["correlationId"] == r.headers["x-correlation-id"]
    assert set(body) >= {"status", "error", "code", "message", "path", "correlationId", "timestamp"}


def test_wrong_method_uses_the_error_contract(client):
    r = client.post("/health")
    assert (r.status_code, r.json()["code"]) == (405, "METHOD_NOT_ALLOWED")


def test_unexpected_error_does_not_leak_details(settings):
    app = create_app(settings)

    @app.get("/boom")
    async def boom():
        raise RuntimeError("secret internals: password=hunter2")

    with TestClient(app, raise_server_exceptions=False) as c:
        r = c.get("/boom")
    assert (r.status_code, r.json()["code"]) == (500, "INTERNAL_ERROR")
    assert "hunter2" not in r.text and "internals" not in r.text


def test_next_phase_libraries_resolve():
    for module in ("langgraph", "langchain_core", "mcp", "asyncpg", "google.genai"):
        importlib.import_module(module)


def test_llm_is_reported_configured_when_credentials_are_present(tmp_path):
    key = tmp_path / "key.json"
    key.write_text("{}")
    with TestClient(create_app(make_settings(GOOGLE_APPLICATION_CREDENTIALS=str(key)))) as c:
        body = c.get("/health/dependencies").json()
    assert body["dependencies"]["llm"] == {"status": "CONFIGURED", "probed": False, "circuit_breaker": "closed"}
    assert str(key) not in str(body)
